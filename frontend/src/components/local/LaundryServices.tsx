'use client';

import React, { useState, useRef, useEffect } from 'react';
import { 
  Shirt, Sparkles, Sofa, BedDouble, Footprints,
  Star, Heart, ChevronLeft, ChevronRight, Truck, Clock4, Grid3X3
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface LaundryService {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  category: 'laundry' | 'dryclean' | 'carpet' | 'furniture' | 'shoes';
  image: string;
  location: string;
  phone: string;
  services: string[];
  servicesEn: string[];
  delivery?: boolean;
  express?: boolean;
  rating: number;
  reviews: number;
  featured?: boolean;
  new?: boolean;
}

const qudsayaCenterLaundry: LaundryService[] = [
  { id: 'l1', name: 'مغسلة الملابس النظيفة', nameEn: 'Clean Clothes Laundry', type: 'مغسلة ملابس', typeEn: 'Laundry', category: 'laundry', image: 'https://images.unsplash.com/photo-1545173168-9f1947eebb7f?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999111101', services: ['غسيل', 'كي', 'طي'], servicesEn: ['Washing', 'Ironing', 'Folding'], delivery: true, express: true, rating: 4.9, reviews: 195, featured: true },
  { id: 'l2', name: 'مغسلة الأناقة', nameEn: 'Elegance Laundry', type: 'مغسلة ملابس', typeEn: 'Laundry', category: 'laundry', image: 'https://images.unsplash.com/photo-1604335399105-a0c585fd81a1?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - شارع الرئيسي', phone: '0999111102', services: ['غسيل', 'كي'], servicesEn: ['Washing', 'Ironing'], delivery: true, rating: 4.7, reviews: 145, new: true },
  { id: 'd1', name: 'التنظيف الجاف الفاخر', nameEn: 'Luxury Dry Clean', type: 'تنظيف جاف', typeEn: 'Dry Clean', category: 'dryclean', image: 'https://images.unsplash.com/photo-1582735689369-4fe89db7114c?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999222201', services: ['بدلات', 'فساتين', 'سترات'], servicesEn: ['Suits', 'Dresses', 'Jackets'], delivery: true, rating: 4.8, reviews: 165, featured: true },
  { id: 'c1', name: 'مغسلة السجاد الشامل', nameEn: 'Comprehensive Carpet Wash', type: 'تنظيف سجاد', typeEn: 'Carpet Cleaning', category: 'carpet', image: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - المنطقة الصناعية', phone: '0999333301', services: ['سجاد', 'موكيت', 'أرضيات'], servicesEn: ['Carpets', 'Rugs', 'Floors'], delivery: true, rating: 4.7, reviews: 125, featured: true },
  { id: 'f1', name: 'تنظيف المفروشات', nameEn: 'Furniture Cleaning', type: 'تنظيف مفروشات', typeEn: 'Furniture Cleaning', category: 'furniture', image: 'https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الحي الغربي', phone: '0999444401', services: ['كنب', 'مراتب', 'ستائر'], servicesEn: ['Sofas', 'Mattresses', 'Curtains'], rating: 4.6, reviews: 85, new: true },
  { id: 's1', name: 'تنظيف الأحذية', nameEn: 'Shoes Cleaning', type: 'تنظيف أحذية', typeEn: 'Shoes Cleaning', category: 'shoes', image: 'https://images.unsplash.com/photo-1543163521-1bf539c55dd2?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', phone: '0999555501', services: ['تنظيف', 'صبغ', 'إصلاح'], servicesEn: ['Cleaning', 'Dyeing', 'Repair'], rating: 4.7, reviews: 95, featured: true },
];

const qudsayaDahiaLaundry: LaundryService[] = [
  { id: 'dl1', name: 'مغسلة الضاحية', nameEn: 'Dahia Laundry', type: 'مغسلة ملابس', typeEn: 'Laundry', category: 'laundry', image: 'https://images.unsplash.com/photo-1545173168-9f1947eebb7f?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999111104', services: ['غسيل', 'كي', 'توصيل'], servicesEn: ['Washing', 'Ironing', 'Delivery'], delivery: true, express: true, rating: 4.8, reviews: 175, featured: true },
  { id: 'dd1', name: 'التنظيف الجاف - الضاحية', nameEn: 'Dry Clean - Dahia', type: 'تنظيف جاف', typeEn: 'Dry Clean', category: 'dryclean', image: 'https://images.unsplash.com/photo-1582735689369-4fe89db7114c?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999222203', services: ['بدلات', 'فساتين'], servicesEn: ['Suits', 'Dresses'], delivery: true, rating: 4.7, reviews: 142, new: true },
  { id: 'dc1', name: 'مغسلة السجاد - الضاحية', nameEn: 'Carpet Wash - Dahia', type: 'تنظيف سجاد', typeEn: 'Carpet Cleaning', category: 'carpet', image: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - المنطقة الصناعية', phone: '0999333303', services: ['سجاد', 'موكيت'], servicesEn: ['Carpets', 'Rugs'], delivery: true, rating: 4.6, reviews: 98 },
  { id: 'ds1', name: 'تنظيف الأحذية - الضاحية', nameEn: 'Shoes - Dahia', type: 'تنظيف أحذية', typeEn: 'Shoes Cleaning', category: 'shoes', image: 'https://images.unsplash.com/photo-1543163521-1bf539c55dd2?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', phone: '0999555503', services: ['تنظيف', 'صبغ'], servicesEn: ['Cleaning', 'Dyeing'], rating: 4.6, reviews: 68 },
];

const dataByRegion: Record<Region, LaundryService[]> = {
  'qudsaya-center': qudsayaCenterLaundry,
  'qudsaya-dahia': qudsayaDahiaLaundry
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Shirt },
  { id: 'laundry', name: 'مغاسل', nameEn: 'Laundry', icon: Shirt },
  { id: 'dryclean', name: 'تنظيف جاف', nameEn: 'Dry Clean', icon: Sparkles },
  { id: 'carpet', name: 'سجاد', nameEn: 'Carpets', icon: Sofa },
  { id: 'furniture', name: 'مفروشات', nameEn: 'Furniture', icon: BedDouble },
  { id: 'shoes', name: 'أحذية', nameEn: 'Shoes', icon: Footprints },
];

export default function LaundryServices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const laundry = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredLaundry = laundry.filter(l => activeCategory === 'all' || l.category === activeCategory);

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
  }, [filteredLaundry]);

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
          {isArabic ? '🧺 مغاسل' : '🧺 Laundry Services'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
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
            {filteredLaundry.slice(0, 8).map((service) => {
              const isFavorite = favorites.includes(service.id);
              return (
                <div key={service.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-cyan-600/90 text-white">
                      {isArabic ? service.type : service.typeEn}
                    </span>
                    {service.delivery && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 text-[9px] font-bold rounded-full bg-emerald-500/90 text-white flex items-center gap-0.5">
                        <Truck className="w-2.5 h-2.5" />
                        {isArabic ? 'توصيل' : 'Delivery'}
                      </span>
                    )}
                    {service.express && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 text-[9px] font-bold rounded-full bg-orange-500/90 text-white flex items-center gap-0.5">
                        <Clock4 className="w-2.5 h-2.5" />
                        {isArabic ? 'سريع' : 'Express'}
                      </span>
                    )}
                    {service.featured && (
                      <span className="absolute top-2 right-10 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                  </div>
                  <div>
                    <div className="flex items-center gap-0.5 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{service.rating}</span>
                      <span className="text-xs text-gray-400">({service.reviews})</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? service.services : service.servicesEn).slice(0, 2).map((srv, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-cyan-100 text-cyan-700 rounded-full">{srv}</span>
                      ))}
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {service.location}</span>
                      <a href={`tel:${service.phone}`} onClick={(e) => e.stopPropagation()} className="text-cyan-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredLaundry.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredLaundry.slice(0, 4).map((s, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={s.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredLaundry.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع المغاسل' : 'Browse all services'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع المغاسل */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🧺 {isArabic ? 'جميع المغاسل' : 'All Laundry Services'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredLaundry.map((service) => {
                const isFavorite = favorites.includes(service.id);
                return (
                  <div key={service.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-cyan-600/90 text-white">
                        {isArabic ? service.type : service.typeEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">📍 {service.location}</p>
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
