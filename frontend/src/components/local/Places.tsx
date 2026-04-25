'use client';

import React, { useState, useRef, useEffect } from 'react';
import { MapPin, Star, Heart, TreePine, Landmark, Building2, Camera, Church, ShoppingBag, ChevronLeft, ChevronRight, Grid3X3 } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface Place {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  rating: number;
  image: string;
  description: string;
  descriptionEn: string;
  featured?: boolean;
  new?: boolean;
}

const qudsayaDahiaPlaces: Place[] = [
  { id: 'd1', name: 'حديقة ضاحية قدسيا', nameEn: 'Qudsaya Dahia Park', type: 'حدائق', typeEn: 'Parks', rating: 4.5, image: 'https://images.unsplash.com/photo-1519331379826-f10be5486c6f?auto=format&fit=crop&w=300&q=80', description: 'حديقة كبيرة مع ملاعب أطفال', descriptionEn: 'Large park with playgrounds', featured: true },
  { id: 'd2', name: 'نادي الضاحية الرياضية', nameEn: 'Dahia Sports Club', type: 'معالم', typeEn: 'Attractions', rating: 4.7, image: 'https://images.unsplash.com/photo-1571902943202-507ec2618e8f?auto=format&fit=crop&w=300&q=80', description: 'نادي رياضي متكامل', descriptionEn: 'Complete sports club', new: true },
  { id: 'd3', name: 'سوق الضاحية الشعبي', nameEn: 'Dahia Popular Market', type: 'أسواق', typeEn: 'Markets', rating: 4.4, image: 'https://images.unsplash.com/photo-1598892550437-0784ad7f36a6?auto=format&fit=crop&w=300&q=80', description: 'سوق شعبي متنوع', descriptionEn: 'Diverse popular market' },
  { id: 'd4', name: 'جامع الضاحية الكبير', nameEn: 'Grand Dahia Mosque', type: 'معالم دينية', typeEn: 'Religious Sites', rating: 4.8, image: 'https://images.unsplash.com/photo-1542816417-0983c9c9ad53?auto=format&fit=crop&w=300&q=80', description: 'مسجد كبير وجامع', descriptionEn: 'Large and grand mosque', featured: true },
  { id: 'd5', name: 'مطعم الوادي', nameEn: 'Al Wadi Restaurant', type: 'سياحة', typeEn: 'Tourism', rating: 4.6, image: 'https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&w=300&q=80', description: 'مطعم فاخر بإطلالة طبيعية', descriptionEn: 'Fancy restaurant with natural views', new: true },
];

const qudsayaCenterPlaces: Place[] = [
  { id: 'c1', name: 'حديقة قدسيا المركزية', nameEn: 'Qudsaya Central Park', type: 'حدائق', typeEn: 'Parks', rating: 4.6, image: 'https://images.unsplash.com/photo-1519331379826-f10be5486c6f?auto=format&fit=crop&w=300&q=80', description: 'حديقة خلابة مع إطلالة جبلية', descriptionEn: 'Beautiful park with mountain views', featured: true },
  { id: 'c2', name: 'قلعة قدسيا التاريخية', nameEn: 'Historic Qudsaya Castle', type: 'معالم سياحية', typeEn: 'Attractions', rating: 4.9, image: 'https://images.unsplash.com/photo-1554907984-15263bfd63bd?auto=format&fit=crop&w=300&q=80', description: 'معلم تاريخي قديم', descriptionEn: 'Ancient historical landmark', featured: true },
  { id: 'c3', name: 'سوق قدسيا الشعبي', nameEn: 'Qudsaya Popular Market', type: 'أسواق', typeEn: 'Markets', rating: 4.5, image: 'https://images.unsplash.com/photo-1598892550437-0784ad7f36a6?auto=format&fit=crop&w=300&q=80', description: 'سوق تقليدي بمنتجات محلية', descriptionEn: 'Traditional market with local products' },
  { id: 'c4', name: 'جامع قدسيا الكبير', nameEn: 'Grand Qudsaya Mosque', type: 'معالم دينية', typeEn: 'Religious Sites', rating: 4.7, image: 'https://images.unsplash.com/photo-1542816417-0983c9c9ad53?auto=format&fit=crop&w=300&q=80', description: 'مسجد تاريخي', descriptionEn: 'Historic mosque' },
  { id: 'c5', name: 'مقهى الساحة', nameEn: 'Square Café', type: 'سياحة', typeEn: 'Tourism', rating: 4.4, image: 'https://images.unsplash.com/photo-1554118811-1e0d58224f24?auto=format&fit=crop&w=300&q=80', description: 'مقهى عصري في الساحة الرئيسية', descriptionEn: 'Modern café in the main square', new: true },
  { id: 'c6', name: 'كنيسة العذراء', nameEn: 'Church of the Virgin', type: 'معالم دينية', typeEn: 'Religious Sites', rating: 4.8, image: 'https://images.unsplash.com/photo-1548625149-fc4a29cf7092?auto=format&fit=crop&w=300&q=80', description: 'كنيسة تاريخية قديمة', descriptionEn: 'Ancient historic church', featured: true },
];

const placesByRegion: Record<Region, Place[]> = {
  'qudsaya-center': qudsayaCenterPlaces,
  'qudsaya-dahia': qudsayaDahiaPlaces
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: MapPin },
  { id: 'حدائق', name: 'حدائق', nameEn: 'Parks', icon: TreePine },
  { id: 'معالم', name: 'معالم', nameEn: 'Attractions', icon: Landmark },
  { id: 'معالم سياحية', name: 'معالم سياحية', nameEn: 'Tourist Attractions', icon: Camera },
  { id: 'أسواق', name: 'أسواق', nameEn: 'Markets', icon: ShoppingBag },
  { id: 'معالم دينية', name: 'معالم دينية', nameEn: 'Religious', icon: Church },
  { id: 'سياحة', name: 'سياحة', nameEn: 'Tourism', icon: Camera },
];

export default function Places() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const places = placesByRegion[region];

  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredPlaces = places.filter(place => {
    return activeCategory === 'all' || place.type === activeCategory || place.typeEn === activeCategory;
  });

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [filteredPlaces]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🏛️ أماكن' : '🏛️ Places'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            const count = filter.id === 'all' ? places.length : places.filter(p => p.type === filter.id || p.typeEn === filter.id).length;
            if (count === 0 && filter.id !== 'all') return null;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        <div className="relative">
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredPlaces.slice(0, 8).map((place) => {
              const isFavorite = favorites.includes(place.id);
              return (
                <div key={place.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={place.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(place.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-teal-500/90 text-white">
                      {isArabic ? place.type : place.typeEn}
                    </span>
                    {place.featured && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-teal-500 text-white text-[10px] font-semibold rounded-full">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                    {place.new && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 bg-emerald-500 text-white text-[10px] font-semibold rounded-full">
                        {isArabic ? 'جديد' : 'New'}
                      </span>
                    )}
                    <div className="absolute bottom-2 right-2 flex items-center gap-0.5 px-2 py-0.5 bg-black/60 backdrop-blur-sm rounded-full">
                      <Star className="w-3 h-3 fill-amber-400 text-amber-400" />
                      <span className="text-[10px] font-bold text-white">{place.rating}</span>
                    </div>
                  </div>
                  <div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? place.name : place.nameEn}</h3>
                    <p className="text-xs text-gray-500 line-clamp-1">{isArabic ? place.description : place.descriptionEn}</p>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredPlaces.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredPlaces.slice(0, 4).map((p, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={p.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredPlaces.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع الأماكن' : 'Browse all places'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الأماكن */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🏛️ {isArabic ? 'جميع الأماكن' : 'All Places'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredPlaces.map((place) => {
                const isFavorite = favorites.includes(place.id);
                return (
                  <div key={place.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={place.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(place.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-teal-500/90 text-white">
                        {isArabic ? place.type : place.typeEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? place.name : place.nameEn}</h3>
                    <p className="text-xs text-gray-500 line-clamp-1">{isArabic ? place.description : place.descriptionEn}</p>
                  </div>
                );
              })}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
