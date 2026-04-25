'use client';

import React, { useState, useRef, useEffect } from 'react';
import { 
  Store, Shirt, Wrench, Nut, Coffee, Milk, Smartphone, SprayCan, Grid3X3,
  Star, MapPin, Clock, Phone, Heart, ChevronLeft, ChevronRight, Truck
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface RetailShop {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  category: 'clothes' | 'hardware' | 'roastery' | 'grocery' | 'electronics' | 'cleaning';
  image: string;
  location: string;
  hours: string;
  phone: string;
  delivery?: boolean;
  rating: number;
  reviews: number;
  featured?: boolean;
  new?: boolean;
}

const qudsayaCenterShops: RetailShop[] = [
  { id: 'c1', name: 'محلات الأناقة للملابس', nameEn: 'Elegance Clothing Store', type: 'ملابس رجالية', typeEn: "Men's Clothing", category: 'clothes', image: 'https://images.unsplash.com/photo-1441984904996-e0b6ba687e04?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة الرئيسية', hours: '9:00 - 22:00', phone: '0999123456', delivery: true, rating: 4.8, reviews: 120, featured: true },
  { id: 'c2', name: 'بيت الأزياء', nameEn: 'Fashion House', type: 'ملابس نسائية', typeEn: "Women's Clothing", category: 'clothes', image: 'https://images.unsplash.com/photo-1441986300917-64674bd600d8?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - شارع المغتربين', hours: '10:00 - 21:00', phone: '0998234567', delivery: true, rating: 4.7, reviews: 95, new: true },
  { id: 'c3', name: 'ملابس الأطفال', nameEn: 'Kids Clothing', type: 'ملابس أطفال', typeEn: 'Kids Clothing', category: 'clothes', image: 'https://images.unsplash.com/photo-1519238263530-99bdd11df2ea?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الحي الغربي', hours: '9:00 - 20:00', phone: '0997345678', rating: 4.6, reviews: 78 },
  { id: 'h1', name: 'محل الخرداوات الشامل', nameEn: 'General Hardware Store', type: 'خرداوات وأدوات', typeEn: 'Hardware & Tools', category: 'hardware', image: 'https://images.unsplash.com/photo-1581092921461-eab62e97a780?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - المدخل', hours: '7:00 - 19:00', phone: '0999456789', rating: 4.5, reviews: 65, featured: true },
  { id: 'h2', name: 'محل الحدادة', nameEn: 'Iron Works Shop', type: 'حدادة', typeEn: 'Blacksmith', category: 'hardware', image: 'https://images.unsplash.com/photo-1504148455328-c376907d081c?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - المنطقة الصناعية', hours: '8:00 - 18:00', phone: '0998567890', rating: 4.4, reviews: 45 },
  { id: 'r1', name: 'محمصة السلطان', nameEn: 'Sultan Roastery', type: 'مكسرات وبذور', typeEn: 'Nuts & Seeds', category: 'roastery', image: 'https://images.unsplash.com/photo-1599599810769-bcde5a160d32?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', hours: '8:00 - 22:00', phone: '0999678901', delivery: true, rating: 4.9, reviews: 180, featured: true },
  { id: 'r2', name: 'محمصة اللوز الذهبي', nameEn: 'Golden Almond Roastery', type: 'حلويات شرقية', typeEn: 'Eastern Sweets', category: 'roastery', image: 'https://images.unsplash.com/photo-1548848221-0c2e497ed557?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - شارع الرئيسي', hours: '9:00 - 23:00', phone: '0999789012', delivery: true, rating: 4.8, reviews: 145, new: true },
  { id: 'g1', name: 'محل الأجبان والزيتون', nameEn: 'Cheese & Olive Shop', type: 'أجبان وزيتون', typeEn: 'Cheese & Olives', category: 'grocery', image: 'https://images.unsplash.com/photo-1486297678162-eb2a19b0a32d?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', hours: '6:00 - 14:00', phone: '0999890123', rating: 4.7, reviews: 110, featured: true },
  { id: 'g2', name: 'محل البهارات والعطارة', nameEn: 'Spices & Herbs Shop', type: 'بهارات وعطارة', typeEn: 'Spices & Herbs', category: 'grocery', image: 'https://images.unsplash.com/photo-1596040033229-a9821ebd058d?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - السوق الشعبي', hours: '7:00 - 15:00', phone: '0999901234', rating: 4.6, reviews: 88 },
  { id: 'e1', name: 'محل الهواتف الذكية', nameEn: 'Smart Phones Shop', type: 'هواتف ذكية', typeEn: 'Smart Phones', category: 'electronics', image: 'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الشارع الرئيسي', hours: '9:00 - 21:00', phone: '0999123457', delivery: true, rating: 4.7, reviews: 130, featured: true },
  { id: 'e2', name: 'كمبيوترات وتقنية', nameEn: 'Computers & Tech', type: 'كمبيوترات', typeEn: 'Computers', category: 'electronics', image: 'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', hours: '9:00 - 20:00', phone: '0999234568', rating: 4.6, reviews: 95, new: true },
  { id: 'cl1', name: 'محل المنظفات', nameEn: 'Cleaning Supplies', type: 'منظفات', typeEn: 'Cleaning Products', category: 'cleaning', image: 'https://images.unsplash.com/photo-1582735689369-4fe89db7114c?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الحي الغربي', hours: '8:00 - 19:00', phone: '0999345679', delivery: true, rating: 4.4, reviews: 55 }
];

const qudsayaDahiaShops: RetailShop[] = [
  { id: 'd1', name: 'محل الأناقة - الضاحية', nameEn: 'Elegance - Dahia', type: 'ملابس رجالية', typeEn: "Men's Clothing", category: 'clothes', image: 'https://images.unsplash.com/photo-1441984904996-e0b6ba687e04?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', hours: '9:00 - 22:00', phone: '0999456780', delivery: true, rating: 4.7, reviews: 105, featured: true },
  { id: 'd2', name: 'أزياء الحريم', nameEn: "Women's Fashion", type: 'ملابس نسائية', typeEn: "Women's Clothing", category: 'clothes', image: 'https://images.unsplash.com/photo-1441986300917-64674bd600d8?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الحي الرئيسي', hours: '10:00 - 21:00', phone: '0999567891', rating: 4.6, reviews: 88 },
  { id: 'dh1', name: 'خرداوات الضاحية', nameEn: 'Dahia Hardware', type: 'خرداوات وأدوات', typeEn: 'Hardware & Tools', category: 'hardware', image: 'https://images.unsplash.com/photo-1581092921461-eab62e97a780?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - المدخل', hours: '7:00 - 19:00', phone: '0999678902', rating: 4.5, reviews: 60, featured: true },
  { id: 'dr1', name: 'محمصة النور', nameEn: 'Al-Noor Roastery', type: 'مكسرات وبذور', typeEn: 'Nuts & Seeds', category: 'roastery', image: 'https://images.unsplash.com/photo-1599599810769-bcde5a160d32?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', hours: '8:00 - 22:00', phone: '0999789013', delivery: true, rating: 4.8, reviews: 165, featured: true },
  { id: 'dg1', name: 'محل الألبان الطازجة', nameEn: 'Fresh Dairy Shop', type: 'أجبان وألبان', typeEn: 'Cheese & Dairy', category: 'grocery', image: 'https://images.unsplash.com/photo-1628088062854-d1870b4553da?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الحي الشمالي', hours: '6:00 - 13:00', phone: '0999890124', rating: 4.7, reviews: 98 },
  { id: 'de1', name: 'إلكترونيات الضاحية', nameEn: 'Dahia Electronics', type: 'هواتف وإكسسوارات', typeEn: 'Phones & Accessories', category: 'electronics', image: 'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - المركز التجاري', hours: '9:00 - 21:00', phone: '0999012346', delivery: true, rating: 4.6, reviews: 110, new: true }
];

const dataByRegion: Record<Region, RetailShop[]> = {
  'qudsaya-center': qudsayaCenterShops,
  'qudsaya-dahia': qudsayaDahiaShops
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Store },
  { id: 'clothes', name: 'ألبسة', nameEn: 'Clothing', icon: Shirt },
  { id: 'hardware', name: 'خرداوات', nameEn: 'Hardware', icon: Wrench },
  { id: 'roastery', name: 'محامص', nameEn: 'Roastery', icon: Nut },
  { id: 'grocery', name: 'غذائية', nameEn: 'Grocery', icon: Milk },
  { id: 'electronics', name: 'إلكترونيات', nameEn: 'Electronics', icon: Smartphone },
  { id: 'cleaning', name: 'تنظيف', nameEn: 'Cleaning', icon: SprayCan },
];

export default function RetailShops() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const shops = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredShops = shops.filter(shop => activeCategory === 'all' || shop.category === activeCategory);

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
  }, [filteredShops]);

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
          {isArabic ? '🛒 محلات تجارية' : '🛒 Retail Shops'}
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

        {/* Horizontal Scrolling Container - Airbnb Style */}
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
            {filteredShops.slice(0, 8).map((shop) => {
              const isFavorite = favorites.includes(shop.id);
              return (
                <div key={shop.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={shop.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(shop.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-amber-600/90 text-white">
                      {isArabic ? shop.type : shop.typeEn}
                    </span>
                    <div className="absolute bottom-2 right-2 flex gap-1">
                      {shop.delivery && (
                        <span className="px-2 py-0.5 text-[9px] font-bold rounded-full bg-emerald-500 text-white flex items-center gap-0.5">
                          <Truck className="w-2.5 h-2.5" />
                          {isArabic ? 'توصيل' : 'Delivery'}
                        </span>
                      )}
                      {shop.featured && (
                        <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-white/90 text-gray-900">
                          {isArabic ? 'مميز' : 'Featured'}
                        </span>
                      )}
                      {shop.new && (
                        <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-blue-500 text-white">
                          {isArabic ? 'جديد' : 'New'}
                        </span>
                      )}
                    </div>
                  </div>
                  <div>
                    <div className="flex items-center gap-1 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{shop.rating}</span>
                      <span className="text-xs text-gray-400">({shop.reviews})</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? shop.name : shop.nameEn}</h3>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {shop.location}</span>
                      <a href={`tel:${shop.phone}`} onClick={(e) => e.stopPropagation()} className="text-amber-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredShops.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredShops.slice(0, 4).map((s, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={s.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredShops.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع المحلات' : 'Browse all shops'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع المحلات */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🛒 {isArabic ? 'جميع المحلات التجارية' : 'All Retail Shops'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredShops.map((shop) => {
                const isFavorite = favorites.includes(shop.id);
                return (
                  <div key={shop.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={shop.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(shop.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-amber-600/90 text-white">
                        {isArabic ? shop.type : shop.typeEn}
                      </span>
                      {shop.delivery && (
                        <span className="absolute bottom-2 right-2 px-2 py-0.5 text-[9px] font-bold rounded-full bg-emerald-500 text-white flex items-center gap-0.5">
                          <Truck className="w-2.5 h-2.5" />
                          {isArabic ? 'توصيل' : 'Delivery'}
                        </span>
                      )}
                    </div>
                    <div>
                      <div className="flex items-center gap-1 mb-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{shop.rating}</span>
                      </div>
                      <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? shop.name : shop.nameEn}</h3>
                      <p className="text-xs text-gray-500 truncate">📍 {shop.location}</p>
                    </div>
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
