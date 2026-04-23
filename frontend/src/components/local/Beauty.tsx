'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Sparkles, Star, Heart, ChevronLeft, ChevronRight, Phone } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface BeautyPlace {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  rating: number;
  image: string;
  services: string[];
  servicesEn: string[];
  location: string;
  phone: string;
  featured?: boolean;
}

const qudsayaCenterBeauty: BeautyPlace[] = [
  { id: '1', name: 'صالون الأناقة', nameEn: 'Elegance Salon', type: 'صالون نسائي', typeEn: 'Women\'s Salon', rating: 4.9, image: 'https://images.unsplash.com/photo-1560066984-138dadb4c035?auto=format&fit=crop&w=400&q=80', services: ['قص شعر', 'صبغة', 'مانيكير'], servicesEn: ['Haircut', 'Dye', 'Manicure'], location: 'قدسيا - الحي الرئيسي', phone: '0999123456', featured: true },
  { id: '2', name: 'حلاق الفارس', nameEn: 'Al-Fares Barber', type: 'حلاقة رجالية', typeEn: 'Men\'s Barber', rating: 4.7, image: 'https://images.unsplash.com/photo-1503951914875-452162b0f3f1?auto=format&fit=crop&w=400&q=80', services: ['حلاقة', 'تقصير', 'حلاقة لحية'], servicesEn: ['Shave', 'Haircut', 'Beard'], location: 'قدسيا', phone: '0998765432' },
  { id: '3', name: 'سبا الاسترخاء', nameEn: 'Relaxation Spa', type: 'مركز سبا', typeEn: 'Spa Center', rating: 4.8, image: 'https://images.unsplash.com/photo-1540555700478-4be289fbecef?auto=format&fit=crop&w=400&q=80', services: ['مساج', 'ساونا', 'جاكوزي'], servicesEn: ['Massage', 'Sauna', 'Jacuzzi'], location: 'قدسيا - المركز', phone: '0111234567', featured: true },
  { id: '4', name: 'مركز التجميل', nameEn: 'Beauty Center', type: 'تجميل', typeEn: 'Beauty', rating: 4.6, image: 'https://images.unsplash.com/photo-1522337360788-8b13dee7a37e?auto=format&fit=crop&w=400&q=80', services: ['مكياج', 'عناية بالبشرة', 'تنظيف'], servicesEn: ['Makeup', 'Skincare', 'Cleaning'], location: 'قدسيا', phone: '0997654321' },
  { id: '5', name: 'سبا الياسمين', nameEn: 'Jasmine Spa', type: 'مركز سبا', typeEn: 'Spa Center', rating: 4.9, image: 'https://images.unsplash.com/photo-1600334089648-b0d9d3028eb2?auto=format&fit=crop&w=400&q=80', services: ['مساج', 'عناية', 'استرخاء'], servicesEn: ['Massage', 'Care', 'Relaxation'], location: 'قدسيا - الحي الغربي', phone: '0998456789', featured: true },
];

const qudsayaDahiaBeauty: BeautyPlace[] = [
  { id: '1', name: 'صالون الجمال', nameEn: 'Beauty Salon', type: 'صالون نسائي', typeEn: 'Women\'s Salon', rating: 4.8, image: 'https://images.unsplash.com/photo-1560066984-138dadb4c035?auto=format&fit=crop&w=400&q=80', services: ['قص شعر', 'صبغة', 'مانيكير'], servicesEn: ['Haircut', 'Dye', 'Manicure'], location: 'الضاحية - الحي الرئيسي', phone: '0999234567', featured: true },
  { id: '2', name: 'حلاق النجوم', nameEn: 'Stars Barber', type: 'حلاقة رجالية', typeEn: 'Men\'s Barber', rating: 4.6, image: 'https://images.unsplash.com/photo-1503951914875-452162b0f3f1?auto=format&fit=crop&w=400&q=80', services: ['حلاقة', 'تقصير', 'حلاقة لحية'], servicesEn: ['Shave', 'Haircut', 'Beard'], location: 'الضاحية', phone: '0998345678' },
  { id: '3', name: 'سبا الراحة', nameEn: 'Comfort Spa', type: 'مركز سبا', typeEn: 'Spa Center', rating: 4.9, image: 'https://images.unsplash.com/photo-1540555700478-4be289fbecef?auto=format&fit=crop&w=400&q=80', services: ['مساج', 'ساونا', 'جاكوزي'], servicesEn: ['Massage', 'Sauna', 'Jacuzzi'], location: 'الضاحية - المركز', phone: '0112345678', featured: true },
  { id: '4', name: 'حلاق الأناقة', nameEn: 'Elegance Barber', type: 'حلاقة رجالية', typeEn: 'Men\'s Barber', rating: 4.8, image: 'https://images.unsplash.com/photo-1599351431202-1e0f0137899a?auto=format&fit=crop&w=400&q=80', services: ['حلاقة', 'حلاقة لحية', 'ستايل'], servicesEn: ['Shave', 'Beard', 'Style'], location: 'الضاحية - الحي الغربي', phone: '0999567890' },
];

const dataByRegion: Record<Region, BeautyPlace[]> = {
  'qudsaya-center': qudsayaCenterBeauty,
  'qudsaya-dahia': qudsayaDahiaBeauty
};

export default function Beauty() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const beautyPlaces = dataByRegion[region];
  
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

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
  }, [beautyPlaces]);

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
          {isArabic ? '💇‍♀️ تجميل' : '💇‍♀️ Beauty'}
        </h2>

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
            {beautyPlaces.slice(0, 8).map((place) => {
              const isFavorite = favorites.includes(place.id);
              return (
                <div key={place.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={place.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(place.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-fuchsia-600/90 text-white">
                      {isArabic ? place.type : place.typeEn}
                    </span>
                    {place.featured && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                    <div className="absolute bottom-2 right-2 flex items-center gap-0.5 px-2 py-0.5 bg-black/60 backdrop-blur-sm rounded-full">
                      <Star className="w-3 h-3 fill-amber-400 text-amber-400" />
                      <span className="text-[10px] font-bold text-white">{place.rating}</span>
                    </div>
                  </div>
                  <div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? place.name : place.nameEn}</h3>
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? place.services : place.servicesEn).slice(0, 2).map((service, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-fuchsia-100 text-fuchsia-700 rounded-full">{service}</span>
                      ))}
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {place.location}</span>
                      <a href={`tel:${place.phone}`} onClick={(e) => e.stopPropagation()} className="text-fuchsia-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* View All Card */}
            <button
              onClick={() => setShowAll(true)}
              className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
            >
              <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
                <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                  {beautyPlaces.slice(0, 4).map((p, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={p.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{beautyPlaces.length} {isArabic ? 'مكان' : 'places'}</span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع مراكز التجميل' : 'All Beauty Places'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {beautyPlaces.length} {isArabic ? 'مكان متاح' : 'places available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {beautyPlaces.map((place) => {
                const isFavorite = favorites.includes(place.id);
                return (
                  <div key={place.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={place.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(place.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-fuchsia-600/90 text-white">
                        {isArabic ? place.type : place.typeEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? place.name : place.nameEn}</h3>
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? place.services : place.servicesEn).slice(0, 2).map((service, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-fuchsia-100 text-fuchsia-700 rounded-full">{service}</span>
                      ))}
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {place.location}</span>
                      <a href={`tel:${place.phone}`} onClick={(e) => e.stopPropagation()} className="text-fuchsia-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </Drawer.Content>
      </Drawer.Root>
    </section>
  );
}
